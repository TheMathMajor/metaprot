import React, {Component} from 'react';
import { Link } from 'react-router-dom';


var tabData = [
    {name: "Upload Data", path: "/upload" },
    {name: "Preprocessing", path: "/upload-pass" },
    {name: "Analysis", path: "/analysis" },
    {name: "Annotation", path: "#" },
    {name: "Integration", path: "#" },
    {name: "Summary", path: "#" },
];

var Tab = React.createClass({
  render: function() {
    return (
      <li
        onClick={this.props.handleClick}
        className={this.props.isActive ? "active" : null}>
        <a href={this.props.data.path}>{this.props.data.name}</a>
      </li>
    );
  }
});

class SideNavBar extends Component {
    constructor(props) {
        super(props);
        this.state = {
            activeTab: tabData[0]
        }
        this.handleClick = this.handleClick.bind(this);
    }

    handleClick(tab) {
        this.setState({activeTab: tab});
    }

    render() {
        return (
             <div className="col-sm-3 col-md-2 sidebar sidebar-left sidebar-animate sidebar-md-show">
                <ul className="nav nav-sidebar">
                    {tabData.map(function(tab){
                        return (
                            <Tab data={tab}
                            isActive={this.state.activeTab === tab}
                            handleClick={this.handleClick.bind(this,tab)} />
                        );
                    }.bind(this))}
                </ul>
            </div>

        )

    }




}

export default SideNavBar



